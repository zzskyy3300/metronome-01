package com.example.metronome.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.metronome.R
import com.example.metronome.audio.PlaybackStatus
import com.example.metronome.data.Language
import com.example.metronome.data.SoundType
import com.example.metronome.data.TimeSignature
import com.example.metronome.ui.theme.BpmDisplay
import com.example.metronome.ui.theme.Muted
import com.example.metronome.ui.theme.Primary
import com.example.metronome.ui.theme.StrongBeat
import com.example.metronome.ui.theme.WeakBeat
import com.example.metronome.vm.MetronomeViewModel
import com.example.metronome.vm.UiState
import kotlin.math.roundToInt

/**
 * 主界面：深色极简风格。
 *
 * 布局自上而下：
 * 1. 顶部双行：第一行 标题 + 小号版本号；第二行 语言切换按钮（居中）
 * 2. 节拍闪烁圆点行（按当前拍号渲染 beatCount 个圆点）
 * 3. 大字体 BPM 显示
 * 4. BPM 调整按钮 (-10 / -1 / +1 / +10) + 滑块 30..220
 * 5. 主控制 播放/暂停/继续/停止
 * 6. 拍号选择
 * 7. 音色选择（6 种）
 * 8. 重拍开关 + 音量滑块
 * 9. 练习计时器（0/5/10/15/20/30 分钟 + 剩余时间显示）
 *
 * Tap Tempo 功能暂时从界面移除（TapTempoButton 暂不调用，ViewModel 方法保留以便后续恢复）。
 *
 * 全部文案走 string.xml（中英双语）。
 */
@Composable
fun MainScreen(vm: MetronomeViewModel) {
    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(ui = ui, onToggleLanguage = vm::toggleLanguage)

            Spacer(Modifier.height(16.dp))
            BeatIndicatorRow(ui = ui)

            Spacer(Modifier.height(8.dp))
            BpmDisplayBlock(ui = ui)

            Spacer(Modifier.height(12.dp))
            BpmAdjustButtons(ui = ui, onSet = vm::setBpm)

            Spacer(Modifier.height(8.dp))
            BpmSlider(ui = ui, onSet = vm::setBpm)

            Spacer(Modifier.height(20.dp))
            MainControlButtons(ui = ui, onPlay = vm::play, onPause = vm::pause, onResume = vm::resume, onStop = vm::stop)

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Muted.copy(alpha = 0.5f))

            Spacer(Modifier.height(16.dp))
            TimeSignaturePicker(ui = ui, onPick = vm::setTimeSignature)

            Spacer(Modifier.height(16.dp))
            SoundPicker(ui = ui, onPick = vm::setSound)

            Spacer(Modifier.height(16.dp))
            AccentSwitchRow(ui = ui, onToggle = vm::toggleAccent)

            Spacer(Modifier.height(12.dp))
            VolumeSlider(ui = ui, onSet = vm::setVolume)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Muted.copy(alpha = 0.5f))

            Spacer(Modifier.height(16.dp))
            PracticeTimerBlock(ui = ui, onSet = vm::setPracticeMinutes)

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------- 顶部栏 ----------------

@Composable
private fun TopBar(ui: UiState, onToggleLanguage: () -> Unit) {
    // 双行垂直布局：标题独占一行居中，语言按钮独立成行居中。
    // 修复英文模式下 "Language · English" 与标题同排导致空间不足文字被压成竖排的问题。
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 第一行：标题（app_name）+ 小号版本号，居中
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "v1.10",
                fontSize = 14.sp,
                color = Muted,
                letterSpacing = 1.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        // 第二行：语言切换按钮，居中
        OutlinedButton(onClick = onToggleLanguage) {
            Text(
                text = stringResource(R.string.language) + " · " +
                    stringResource(if (ui.language == Language.ZH) R.string.chinese else R.string.english),
            )
        }
    }
}

// ---------------- 节拍闪烁圆点 ----------------

@Composable
private fun BeatIndicatorRow(ui: UiState) {
    val beatCount = ui.timeSignature.beatCount
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until beatCount) {
            val active = ui.status == PlaybackStatus.PLAYING && ui.currentBeat == i
            val isAccent = i == 0 && ui.accentEnabled
            val baseColor = if (isAccent) StrongBeat else WeakBeat
            // 当前拍闪烁：放大 + 提亮
            val scale by animateFloatAsState(
                targetValue = if (active) 1.4f else 1.0f,
                animationSpec = spring(),
                label = "beatScale",
            )
            val color = if (active) baseColor else baseColor.copy(alpha = 0.35f)
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size((28 * scale).dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

// ---------------- BPM 大字体显示 ----------------

@Composable
private fun BpmDisplayBlock(ui: UiState) {
    // 自适应：BPM 三位数字字号在小屏略缩
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val bpmFontSize = if (screenWidthDp < 360) 84.sp else 112.sp
    val bpmStyle = BpmDisplay.copy(fontSize = bpmFontSize)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = ui.bpm.toString(),
            style = bpmStyle,
            color = Primary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.bpm),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Muted,
            letterSpacing = 2.sp,
        )
        ui.tapBpm?.let { tap ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Tap → $tap",
                fontSize = 14.sp,
                color = OnBackgroundMuted(),
            )
        }
    }
}

@Composable
private fun OnBackgroundMuted(): Color =
    androidx.compose.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)

// ---------------- BPM 加减按钮 ----------------

@Composable
private fun BpmAdjustButtons(ui: UiState, onSet: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdjustChip(text = "-10", onClick = { onSet(ui.bpm - 10) })
        Spacer(Modifier.width(12.dp))
        AdjustChip(text = "-1", onClick = { onSet(ui.bpm - 1) })
        Spacer(Modifier.width(12.dp))
        AdjustChip(text = "+1", onClick = { onSet(ui.bpm + 1) })
        Spacer(Modifier.width(12.dp))
        AdjustChip(text = "+10", onClick = { onSet(ui.bpm + 10) })
    }
}

@Composable
private fun AdjustChip(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primary)
        }
    }
}

// ---------------- BPM 滑块 ----------------

@Composable
private fun BpmSlider(ui: UiState, onSet: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = ui.bpm.toFloat(),
            onValueChange = { onSet(it.roundToInt()) },
            valueRange = 30f..220f,
            colors = SliderDefaults.colors(
                thumbColor = Primary,
                activeTrackColor = Primary,
                inactiveTrackColor = Muted,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("30", fontSize = 12.sp, color = Muted)
            Text("220", fontSize = 12.sp, color = Muted)
        }
    }
}

// ---------------- Tap Tempo 大圆按钮 ----------------

@Composable
private fun TapTempoButton(ui: UiState, onTap: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .clickable(onClick = onTap),
            color = Primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.tap_tempo),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                    )
                    Text(
                        text = stringResource(R.string.tap_hint),
                        fontSize = 11.sp,
                        color = Color.Black.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

// ---------------- 主控制 播放/暂停/继续/停止 ----------------

@Composable
private fun MainControlButtons(
    ui: UiState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 主按钮：IDLE→Play，PLAYING→Pause，PAUSED→Resume
        Button(
            onClick = when (ui.status) {
                PlaybackStatus.PLAYING -> onPause
                PlaybackStatus.PAUSED -> onResume
                PlaybackStatus.IDLE -> onPlay
            },
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.Black),
            modifier = Modifier.size(width = 160.dp, height = 56.dp),
        ) {
            val label = when (ui.status) {
                PlaybackStatus.PLAYING -> stringResource(R.string.pause)
                PlaybackStatus.PAUSED -> stringResource(R.string.resume)
                PlaybackStatus.IDLE -> stringResource(R.string.play)
            }
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        // 副按钮：停止（仅播放/暂停时可用）
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.height(56.dp),
            enabled = ui.status != PlaybackStatus.IDLE,
        ) {
            Text(stringResource(R.string.stop))
        }
    }
}

// ---------------- 拍号选择 ----------------

@Composable
private fun TimeSignaturePicker(ui: UiState, onPick: (TimeSignature) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.time_signature),
            fontSize = 14.sp,
            color = Muted,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TimeSignature.entries.forEach { ts ->
                val selected = ui.timeSignature == ts
                PickerChip(
                    text = ts.display,
                    selected = selected,
                    modifier = Modifier.weight(1f),
                    onClick = { onPick(ts) },
                )
            }
        }
    }
}

// ---------------- 音色选择 ----------------

@Composable
private fun SoundPicker(ui: UiState, onPick: (SoundType) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.sound),
            fontSize = 14.sp,
            color = Muted,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        // 两行三列
        val rows = SoundType.entries.chunked(3)
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { st ->
                    val selected = ui.sound == st
                    PickerChip(
                        text = stringResource(st.stringRes),
                        selected = selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(st) },
                    )
                }
                // 补齐空位保证对齐
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PickerChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) Primary else androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) Color.Black else androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        color = bg,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = fg,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

// ---------------- 重拍开关 ----------------

@Composable
private fun AccentSwitchRow(ui: UiState, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.accent), fontSize = 16.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground)
        Switch(
            checked = ui.accentEnabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(checkedThumbColor = Primary, checkedTrackColor = Primary.copy(alpha = 0.4f)),
        )
    }
}

// ---------------- 音量滑块 ----------------

@Composable
private fun VolumeSlider(ui: UiState, onSet: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.volume), fontSize = 16.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground)
            Text("${(ui.masterVolume * 100).roundToInt()}%", fontSize = 14.sp, color = Muted)
        }
        Slider(
            value = ui.masterVolume,
            onValueChange = { onSet(it) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Primary,
                activeTrackColor = Primary,
                inactiveTrackColor = Muted,
            ),
        )
    }
}

// ---------------- 练习计时器 ----------------

@Composable
private fun PracticeTimerBlock(ui: UiState, onSet: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.practice_timer), fontSize = 16.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground)
            if (ui.practiceActive || ui.practiceRemainingMs > 0L) {
                Text(
                    text = stringResource(R.string.remaining) + ": " + formatMs(ui.practiceRemainingMs),
                    fontSize = 14.sp,
                    color = Primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val options = listOf(0, 5, 10, 15, 20, 30)
            options.forEach { m ->
                val selected = ui.practiceMinutes == m
                PickerChip(
                    text = if (m == 0) stringResource(R.string.off) else m.toString(),
                    selected = selected,
                    onClick = { onSet(m) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 毫秒转 mm:ss。 */
private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mm = totalSec / 60
    val ss = totalSec % 60
    return "%02d:%02d".format(mm, ss)
}
