# 超级节拍器 · Super Metronome

> 基于 Kotlin + Jetpack Compose + AudioTrack 的低延迟节拍器 Android App。零漂移、息屏/后台持续播放、状态栏通知暂停/继续，纯本地无联网。
>
> A low-latency metronome Android app built with Kotlin + Jetpack Compose + AudioTrack. Zero drift, keeps playing when screen-off / backgrounded, status-bar notification with Pause/Resume controls. Fully offline, no network.

---

## 目录 · Contents

1. [中文](#中文)
2. [English](#english)

---

# 中文

## 简介

「超级节拍器」是一款面向乐手的极简节拍器应用，主打**长时间运行零累积漂移**与**低延迟首拍**，并支持息屏 / 后台持续播放。所有音频资源（除男声 / 女声人声外）由代码合成，包体 < 10 MB，无任何第三方埋点 / 广告 / 联网 SDK。

## 功能

- BPM 30 ~ 220（加减按钮 ±1/±10 + 滑块）
- 拍号 2/4、3/4、4/4、6/8、12/8（简单拍 + 复合拍）
- 重拍区分（首拍高亮 + 更响）
- 6 种内置音色：**男声 / 女声 / 响木 / 正弦 / 方波 / 木鱼**
  - 响木、正弦、方波、木鱼：纯代码生成波形，无外部音频文件
  - **男声 / 女声**：需用户自行放入 WAV 语音文件（见下方「男声 / 女声音色资源」）；文件缺失时自动回退到正弦短音，App 仍可正常使用
- 独立音量调节
- 可视化节拍闪烁（重拍红、弱拍蓝，按拍号渲染圆点）
- 练习计时器（0/5/10/15/20/30 分钟，倒计时到点自动停止）
- 参数本地持久化（SharedPreferences，重启后保持）
- 中英双语 UI（顶部一键切换）
- 深色极简风格，大字体 BPM 显示

> **默认值**：音色 = 女声，拍号 = 4/4。

## 技术栈

- Kotlin 2.2.10
- Jetpack Compose（BOM 2026.02.01）+ Material3
- AGP 9.2.1 / Gradle 9.4.1（wrapper 自带）
- 最低 Android 8.0（API 26）/ 目标 Android 14（API 34）
- MVVM 极简架构
- 音频底层：AudioTrack（MODE_STATIC + setLoopPoints + getTimestamp 同步）
- 本地存储：SharedPreferences
- **无任何第三方埋点 / 广告 / 联网 SDK**，仅 AndroidX

## 环境要求

- JDK 17 或更高（Gradle 9.4.1 需要；用 Android Studio 自带 JBR 即可）
- Android SDK（已安装平台 API 36；`local.properties` 指向 `sdk.dir`）
- 无需单独安装 Gradle，工程自带 `gradlew` / `gradlew.bat`（Gradle 9.4.1）
- 首次构建会经 foojay toolchain resolver 自动下载编译用 JDK，需联网

## 工程结构

```
metronome-01/
├─ settings.gradle.kts            # 仓库 + 模块配置 + foojay toolchain
├─ build.gradle.kts               # 根：插件 apply false
├─ gradle.properties             # JVM 参数 + AndroidX 标志
├─ local.properties              # sdk.dir（本地，不入库）
├─ gradle/libs.versions.toml      # 版本目录
├─ gradlew / gradlew.bat         # Gradle 9.4.1 wrapper
├─ gradle/wrapper/               # wrapper jar + properties
├─ keystore.properties           # release 签名配置（本地，不入库）
├─ keystore/metronome.jks        # release 签名密钥（本地，不入库）
├─ proguard-rules.pro            # R8 keep 规则
├─ README.md                     # 本文件
└─ app/
   ├─ build.gradle.kts           # signingConfigs.release + R8 + 依赖
   └─ src/main/
      ├─ AndroidManifest.xml     # FGS(mediaPlayback) + 权限
      ├─ assets/voice/           # 男声/女声 WAV（16 个，见下）
      ├─ res/
      │  ├─ values/strings.xml          # 中文（默认）
      │  ├─ values-en/strings.xml      # 英文
      │  ├─ values/themes.xml          # 深色无 ActionBar
      │  ├─ values/colors.xml
      │  └─ drawable/ic_launcher.xml   # 矢量图标
      └─ java/com/example/metronome/
         ├─ MainActivity.kt
         ├─ data/
         │  ├─ Models.kt              # TimeSignature / SoundType / Language
         │  └─ SettingsRepository.kt  # SharedPreferences 持久化
         ├─ audio/
         │  ├─ WaveformSynthesizer.kt # 合成音色 PCM 生成
         │  └─ MetronomeEngine.kt     # AudioTrack 引擎（零漂移、低延迟、WAV 解码）
         ├─ service/
         │  └─ MetronomeService.kt    # 前台服务 + 通知（暂停/继续/停止）
         ├─ vm/
         │  └─ MetronomeViewModel.kt  # MVVM 状态聚合 + 命令
         └─ ui/
            ├─ theme/{Color,Type,Theme}.kt  # 深色主题
            └─ screens/MainScreen.kt        # Compose 主界面
```

## 编译

> 以下命令在工程根目录执行。Windows 用 `.\gradlew.bat`，类 Unix 用 `./gradlew`。

### 设置 JAVA_HOME（仅当系统未配置时）

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
```

### Debug 构建

```powershell
.\gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### Release 签名构建

签名密钥已生成于 `keystore/metronome.jks`，配置见 `keystore.properties`：

```properties
storeFile=keystore/metronome.jks
storePassword=metronome
keyAlias=metronome
keyPassword=metronome
```

执行：

```powershell
.\gradlew.bat assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`（已签名、R8 混淆 + 资源压缩）

## 男声 / 女声音色资源

App 原生不内置任何外部音频文件；男声 / 女声两种音色需用户自行准备 WAV 文件放入 `assets`。**文件缺失不影响编译与运行**，引擎会回退到正弦短音；但要让这两种音色真正发出"人声数拍"，必须按下列规范放齐 16 个文件。

### 资源要求

| 项 | 要求 |
| --- | --- |
| 文件格式 | **WAV**，PCM 16-bit（未压缩；不支持 float / A-law / μ-law / MP3 等封装） |
| 声道 | **单声道（mono）**优先；立体声会被降混为单声道（左右平均） |
| 采样率 | **推荐 48000 Hz**（与设备原生率一致，避免二次重采样）；其他常见率也接受，引擎会线性重采样 |
| 时长 | **300 ~ 400 ms / 文件**；超出当前拍长时引擎会**截断**，不会拖到下一拍 |
| 文件大小 | 单声道 48 kHz / 16-bit / 400 ms ≈ **38 KB/个**；16 个合计约 **600 KB** |
| 录制建议 | 干声、无背景音乐、无回声；普通话 / 标准英语；正常语速；首采样点静音起始，避免开头爆音 |
| 归一化 | 推荐 peak 归一到 -3 dBFS，避免削波；引擎仅按 masterVolume 缩放 |

### 命名与目录

目录：

```
app/src/main/assets/voice/
```

命名规则（**全小写**，严格匹配）：

```
{gender}_{language}_{beatNumber}.wav
```

- `gender`：`male` 或 `female`
- `language`：`zh` 或 `en`
- `beatNumber`：`1` / `2` / `3` / `4`（对应小节内第几拍；**1 = 重拍**）

共 16 个文件：

```
male_zh_1.wav   male_zh_2.wav   male_zh_3.wav   male_zh_4.wav
male_en_1.wav   male_en_2.wav   male_en_3.wav   male_en_4.wav
female_zh_1.wav female_zh_2.wav female_zh_3.wav female_zh_4.wav
female_en_1.wav female_en_2.wav female_en_3.wav female_en_4.wav
```

### 语音内容

- **中文（`*_zh_*.wav`）**：依次朗读"一、二、三、四"
- **英文（`*_en_*.wav`）**：依次朗读"one, two, three, four"
- `beatNumber=1` 始终对应**重拍**，引擎会自动加重，录制时无需刻意更大声

### 语言切换逻辑

- UI 切换语言 → 引擎按新语言加载对应 WAV 集
- 中文 + 男声 → `male_zh_1~4.wav`；切到英文 → 即刻改用 `male_en_1~4.wav`
- 拍号 > 4（6/8 视为 2 拍、12/8 视为 4 拍）超出 4 拍时，多余拍回退到正弦短音

### 资源制作工具建议（可选）

- 录制 / 剪辑：Audacity（导出 WAV 16-bit PCM mono 48 kHz）
- 文本转语音（TTS）：
  - 中文：Edge TTS 女声 `zh-CN-XiaoxiaoNeural`、男声 `zh-CN-YunyangNeural`
  - 英文：Edge TTS 女声 `en-US-JennyNeural`、男声 `en-US-GuyNeural`
- 导出后放入 `app/src/main/assets/voice/`，重新 `assembleRelease` 即可打包进 APK

## 签名密钥（自用，仅本地）

- 密钥库：`keystore/metronome.jks`（RSA 2048，有效期 10000 天）
- alias / storepass / keypass 均为 `metronome`
- 重新生成：

```powershell
keytool -genkeypair -keystore keystore/metronome.jks -alias metronome `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass metronome -keypass metronome -dname "CN=Metronome"
```

`keystore/metronome.jks` 与 `keystore.properties` 已在 `.gitignore` 中排除。

## 安装与调试

```powershell
# 安装 release APK 到已连接设备/模拟器
adb install -r app\build\outputs\apk\release\app-release.apk

# 查看运行日志（过滤本应用）
adb logcat | Select-String "metronome|MetronomeService"
```

包名 `com.example.metronome`，应用名「超级节拍器 / Super Metronome」。

## 设计要点

### 零漂移音频引擎

- `MODE_STATIC`：整小节 PCM 一次性写入；每小节采样数为整数。
- `setLoopPoints(0, barTotal, -1)`：以硬件采样率循环，循环长度为整数采样数 → 长时间运行无累积漂移。
- 视觉同步：`AudioTrack.getTimestamp`（framePosition + nanoTime）锚点 + `System.nanoTime` 外推计算当前拍序号，与音频输出同一时钟基准。
- 采样率取设备原生率，`USAGE_GAME + PERFORMANCE_MODE_LOW_LATENCY` 追求 < 20ms 首拍延迟。

### 后台保活

- 前台服务类型 `mediaPlayback`（API34 类型化 FGS 必需）。
- `POST_NOTIFICATIONS` 为唯一运行时权限；拒绝时音频照常播放，仅通知按钮不可见。

### 复合拍号

- 6/8 视为 2 拍，12/8 视为 4 拍；`samplesPerBeat = round(60/BPM × sampleRate)`。

---

# English

## Overview

**Super Metronome** is a minimal metronome app for musicians, focused on **zero long-term drift** and **low first-beat latency**, with continuous playback while the screen is off or the app is backgrounded. All audio (except male/female voice counts) is synthesized in code; the APK stays under 10 MB with zero third-party analytics / ads / networking SDKs.

## Features

- BPM 30–220 (±1/±10 buttons + slider)
- Time signatures 2/4, 3/4, 4/4, 6/8, 12/8 (simple + compound)
- Accent beat (first beat highlighted + louder)
- 6 built-in sounds: **Male / Female / Clave / Sine / Square / Woodblock**
  - Clave, Sine, Square, Woodblock: code-synthesized waveforms, no external files
  - **Male / Female**: require user-supplied WAV files (see *Voice Assets* below); missing files fall back to a sine click and the app keeps working
- Independent volume control
- Beat flash visualization (red accent, blue weak, dots per time signature)
- Practice timer (0/5/10/15/20/30 min, auto-stop when done)
- Local persistence via SharedPreferences
- Bilingual UI (Chinese/English, one-tap toggle)
- Dark minimal theme with large BPM display

> **Defaults**: sound = Female, time signature = 4/4.

## Tech Stack

- Kotlin 2.2.10
- Jetpack Compose (BOM 2026.02.01) + Material3
- AGP 9.2.1 / Gradle 9.4.1 (built-in wrapper)
- minSdk 26 (Android 8.0) / targetSdk 34 (Android 14)
- Minimal MVVM architecture
- Audio: AudioTrack (MODE_STATIC + setLoopPoints + getTimestamp sync)
- Storage: SharedPreferences
- **Zero third-party analytics / ads / networking SDKs**, AndroidX only

## Requirements

- JDK 17+ (Gradle 9.4.1; the JBR bundled with Android Studio works)
- Android SDK (platform API 36 installed; `local.properties` → `sdk.dir`)
- No separate Gradle install needed; `gradlew` / `gradlew.bat` (Gradle 9.4.1) is included
- First build fetches the JDK via the foojay toolchain resolver (network required once)

## Project Structure

```
metronome-01/
├─ settings.gradle.kts            # repo + module config + foojay toolchain
├─ build.gradle.kts               # root: plugins apply false
├─ gradle.properties             # JVM args + AndroidX flags
├─ local.properties              # sdk.dir (local, not in VCS)
├─ gradle/libs.versions.toml      # version catalog
├─ gradlew / gradlew.bat         # Gradle 9.4.1 wrapper
├─ gradle/wrapper/               # wrapper jar + properties
├─ keystore.properties           # release signing config (local, not in VCS)
├─ keystore/metronome.jks        # release signing key (local, not in VCS)
├─ proguard-rules.pro            # R8 keep rules
├─ README.md                     # this file
└─ app/
   ├─ build.gradle.kts           # signingConfigs.release + R8 + deps
   └─ src/main/
      ├─ AndroidManifest.xml     # FGS(mediaPlayback) + permissions
      ├─ assets/voice/           # male/female WAVs (16 files, see below)
      ├─ res/
      │  ├─ values/strings.xml          # Chinese (default)
      │  ├─ values-en/strings.xml      # English
      │  ├─ values/themes.xml          # dark, no ActionBar
      │  ├─ values/colors.xml
      │  └─ drawable/ic_launcher.xml   # vector icon
      └─ java/com/example/metronome/
         ├─ MainActivity.kt
         ├─ data/
         │  ├─ Models.kt              # TimeSignature / SoundType / Language
         │  └─ SettingsRepository.kt  # SharedPreferences persistence
         ├─ audio/
         │  ├─ WaveformSynthesizer.kt # synthesized sound PCM generation
         │  └─ MetronomeEngine.kt     # AudioTrack engine (zero drift, low latency, WAV decode)
         ├─ service/
         │  └─ MetronomeService.kt    # foreground service + notification (pause/resume/stop)
         ├─ vm/
         │  └─ MetronomeViewModel.kt  # MVVM state + commands
         └─ ui/
            ├─ theme/{Color,Type,Theme}.kt  # dark theme
            └─ screens/MainScreen.kt        # Compose main screen
```

## Build

> Run from the project root. Windows: `.\gradlew.bat`; Unix: `./gradlew`.

### Set JAVA_HOME (only if not configured system-wide)

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
```

### Debug build

```powershell
.\gradlew.bat assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Signed release build

Signing key lives at `keystore/metronome.jks`, configured in `keystore.properties`:

```properties
storeFile=keystore/metronome.jks
storePassword=metronome
keyAlias=metronome
keyPassword=metronome
```

Run:

```powershell
.\gradlew.bat assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (signed, R8-obfuscated, resources shrunk)

## Voice Assets (Male / Female)

The app ships with no external audio files. The **Male** / **Female** sounds require you to provide WAV files in `assets`. **Missing files do not break the build or runtime** — the engine falls back to a sine click — but for real voice counting you must provide all 16 files per the spec below.

### Requirements

| Item | Requirement |
| --- | --- |
| Format | **WAV**, PCM 16-bit (uncompressed; no float / A-law / μ-law / MP3) |
| Channels | **Mono** preferred; stereo is down-mixed to mono (L+R average) |
| Sample rate | **48000 Hz recommended** (matches native device rate, avoids resampling); other common rates accepted and linearly resampled |
| Duration | **300–400 ms per file**; if longer than the current beat slot the engine **truncates**, never bleeding into the next beat |
| File size | Mono 48 kHz / 16-bit / 400 ms ≈ **38 KB each**; 16 files ≈ **600 KB** total |
| Recording | Dry, no music, no reverb; standard Mandarin / English; normal pace; start on silence to avoid a leading pop |
| Normalization | Peak to −3 dBFS; the engine does not normalize, only applies masterVolume |

### Naming & directory

Directory:

```
app/src/main/assets/voice/
```

Naming convention (**lowercase, exact**):

```
{gender}_{language}_{beatNumber}.wav
```

- `gender`: `male` or `female`
- `language`: `zh` or `en`
- `beatNumber`: `1` / `2` / `3` / `4` (position in the bar; **1 = accent**)

All 16 files:

```
male_zh_1.wav   male_zh_2.wav   male_zh_3.wav   male_zh_4.wav
male_en_1.wav   male_en_2.wav   male_en_3.wav   male_en_4.wav
female_zh_1.wav female_zh_2.wav female_zh_3.wav female_zh_4.wav
female_en_1.wav female_en_2.wav female_en_3.wav female_en_4.wav
```

### Spoken content

- **Chinese (`*_zh_*.wav`)**: count "一、二、三、四"
- **English (`*_en_*.wav`)**: count "one, two, three, four"
- `beatNumber=1` is always the **accent**; the engine boosts it, so record at normal volume.

### Language switching

- Toggling the UI language reloads the matching WAV set in the engine immediately.
- Chinese + Male → `male_zh_1~4.wav`; switch to English → `male_en_1~4.wav` right away.
- Time signatures with > 4 effective beats (6/8 = 2, 12/8 = 4) only read 1..beatCount; beyond that falls back to sine.

### Authoring tools (optional)

- Recording / editing: Audacity (export WAV 16-bit PCM mono 48 kHz)
- TTS:
  - Chinese: Edge TTS female `zh-CN-XiaoxiaoNeural`, male `zh-CN-YunyangNeural`
  - English: Edge TTS female `en-US-JennyNeural`, male `en-US-GuyNeural`
- Drop the files into `app/src/main/assets/voice/` and re-run `assembleRelease`.

## Signing key (local only)

- Keystore: `keystore/metronome.jks` (RSA 2048, 10000-day validity)
- alias / storepass / keypass all `metronome`
- Regenerate:

```powershell
keytool -genkeypair -keystore keystore/metronome.jks -alias metronome `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass metronome -keypass metronome -dname "CN=Metronome"
```

`keystore/metronome.jks` and `keystore.properties` are excluded via `.gitignore`.

## Install & debug

```powershell
# Install the release APK on a connected device/emulator
adb install -r app\build\outputs\apk\release\app-release.apk

# Tail logs for this app
adb logcat | Select-String "metronome|MetronomeService"
```

Package: `com.example.metronome`, app name "超级节拍器 / Super Metronome".

## Design notes

### Zero-drift audio engine

- `MODE_STATIC`: one full bar of PCM written once; integer sample count per bar.
- `setLoopPoints(0, barTotal, -1)`: loops at the hardware sample rate with an integer loop length → no cumulative drift over long runs.
- Visual sync: `AudioTrack.getTimestamp` (framePosition + nanoTime) anchor + `System.nanoTime` extrapolation gives the current beat on the same clock as audio output.
- Uses the device's native sample rate; `USAGE_GAME + PERFORMANCE_MODE_LOW_LATENCY` targets < 20 ms first-beat latency.

### Background playback

- Foreground service type `mediaPlayback` (required for typed FGS on API 34).
- `POST_NOTIFICATIONS` is the only runtime permission; if denied, audio still plays, only the notification buttons are hidden.

### Compound time signatures

- 6/8 treated as 2 beats, 12/8 as 4 beats; `samplesPerBeat = round(60/BPM × sampleRate)`.
